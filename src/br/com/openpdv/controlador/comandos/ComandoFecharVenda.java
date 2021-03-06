package br.com.openpdv.controlador.comandos;

import br.com.openpdv.controlador.core.CoreService;
import br.com.openpdv.controlador.core.Util;
import br.com.openpdv.modelo.Ibpt;
import br.com.openpdv.modelo.core.EComandoSQL;
import br.com.openpdv.modelo.core.OpenPdvException;
import br.com.openpdv.modelo.core.Sql;
import br.com.openpdv.modelo.core.filtro.ECompara;
import br.com.openpdv.modelo.core.filtro.EJuncao;
import br.com.openpdv.modelo.core.filtro.FiltroNumero;
import br.com.openpdv.modelo.core.filtro.FiltroTexto;
import br.com.openpdv.modelo.core.filtro.GrupoFiltro;
import br.com.openpdv.modelo.core.filtro.IFiltro;
import br.com.openpdv.modelo.core.parametro.*;
import br.com.openpdv.modelo.ecf.EcfPagamento;
import br.com.openpdv.modelo.ecf.EcfTrocaProduto;
import br.com.openpdv.modelo.ecf.EcfVenda;
import br.com.openpdv.modelo.ecf.EcfVendaProduto;
import br.com.openpdv.modelo.produto.ProdGrade;
import br.com.openpdv.visao.core.Aguarde;
import br.com.openpdv.visao.core.Caixa;
import br.com.phdss.ECF;
import br.com.phdss.EComandoECF;
import br.com.phdss.TEF;
import br.com.phdss.controlador.PAF;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.swing.JOptionPane;
import org.apache.log4j.Logger;

/**
 * Classe que realiza a acao de fechar uma venda.
 *
 * @author Pedro H. Lira
 */
public class ComandoFecharVenda implements IComando {

    private Logger log;
    private List<EcfPagamento> pagamentos;
    private double bruto;
    private double acres_desc;
    private String obs;
    private EcfVenda venda;

    /**
     * Construtor padrao.
     *
     * @param pagamentos a lista de pagamentos realizados.
     * @param bruto o valor total da venda.
     * @param acres_desc valor de acrescimo (positivo) ou desconto (negativo).
     * @param troco o valor do troco da venda.
     */
    public ComandoFecharVenda(List<EcfPagamento> pagamentos, double bruto, double acres_desc, double troco, String obs) {
        this.log = Logger.getLogger(ComandoFecharVenda.class);
        this.pagamentos = pagamentos;
        this.bruto = bruto;
        this.acres_desc = acres_desc;
        this.venda = Caixa.getInstancia().getVenda();
        this.obs = obs;
    }

    @Override
    public void executar() throws OpenPdvException {
        try {
            TEF.bloquear(true);
            // fecha a venda no cupom
            fecharVendaECF();
            // salva no bd
            fecharVendaBanco();
            // salva o documento para relatorio
            new ComandoSalvarDocumento("RV").executar();
            // salva os pagamentos
            new ComandoSalvarPagamento(pagamentos).executar();
            // coloca na tela
            fecharVendaTela();
            TEF.bloquear(false);
        } catch (OpenPdvException ex) {
            TEF.bloquear(false);
            throw ex;
        } finally {
            Aguarde.getInstancia().setVisible(false);
        }
    }

    @Override
    public void desfazer() throws OpenPdvException {
        // comando nao aplicavel.
    }

    /**
     * Metodo para fechar uma venda no ECF.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaECF() throws OpenPdvException {
        try {
            // sub totaliza
            String AD = Util.formataNumero(acres_desc, 1, 2, false).replace(",", ".");
            StringBuilder sb = new StringBuilder();
            sb.append(Util.formataTexto("MD5: " + PAF.AUXILIAR.getProperty("out.autenticado"), " ", ECF.COL, true));

            // identifica o operador do caixa e o vendedor
            String operador = "OPERADOR: " + venda.getSisUsuario().getSisUsuarioLogin();
            if (venda.getSisVendedor() != null) {
                operador += " - VENDEDOR: " + venda.getSisVendedor().getSisUsuarioLogin();
            }
            sb.append(Util.formataTexto(operador, " ", ECF.COL, true));

            // caso nao tenha sido informado o cliente
            if (venda.getSisCliente() == null) {
                sb.append(Util.formataTexto("CONSUMIDOR NAO INFORMOU O CPF/CNPJ", " ", ECF.COL, true));
            } else if (Caixa.getInstancia().getVenda().isInformouCliente() == false) {
                sb.append("CNPJ/CPF: ").append(venda.getSisCliente().getSisClienteDoc()).append(ECF.SL);
                if (!venda.getSisCliente().getSisClienteNome().equals("")) {
                    sb.append("NOME:     ").append(venda.getSisCliente().getSisClienteNome()).append(ECF.SL);
                }
                if (!venda.getSisCliente().getSisClienteEndereco().equals("")) {
                    sb.append("ENDEREÇO: ").append(venda.getSisCliente().getSisClienteEndereco()).append(ECF.SL);
                }
            }

            // caso seja no estado de MG, colocar o minas legal
            if (PAF.AUXILIAR.getProperty("paf.minas_legal").equalsIgnoreCase("SIM")) {
                sb.append("MINAS LEGAL: ");
                sb.append(PAF.AUXILIAR.getProperty("cli.cnpj")).append(" ");
                sb.append(Util.formataData(venda.getEcfVendaData(), "ddMMyyyy")).append(" ");
                sb.append(Util.formataNumero(bruto + acres_desc, 0, 2, true).replace(",", ""));
            } else if (PAF.AUXILIAR.getProperty("paf.cupom_mania").equalsIgnoreCase("SIM")) {
                // caso seja no estado de RJ, colocar o cupom mania
                sb.append(Util.formataTexto("CUPOM MANIA - CONCORRA A PREMIOS", " ", ECF.COL, true));
                sb.append("ENVIE SMS P/ 6789: ");
                sb.append(Util.formataNumero(PAF.AUXILIAR.getProperty("cli.ie"), 8, 0, false));
                sb.append(Util.formataData(venda.getEcfVendaData(), "ddMMyyyy"));
                sb.append(Util.formataNumero(venda.getEcfVendaCoo(), 6, 0, false));
                sb.append(Util.formataNumero(Caixa.getInstancia().getImpressora().getEcfImpressoraCaixa(), 3, 0, false));
            }

            // caso a opcao de mostrar os valores de impostos esteja ativa
            boolean mostraIbpt = Boolean.valueOf(Util.getConfig().get("nfe.ibpt"));
            if (mostraIbpt) {
                CoreService<Ibpt> service = new CoreService<>();
                double impostos = 0.00;
                double porcent = acres_desc / bruto;

                for (EcfVendaProduto vp : venda.getEcfVendaProdutos()) {
                    if (!vp.getEcfVendaProdutoCancelado()) {
                        FiltroTexto ft = new FiltroTexto("ibptCodigo", ECompara.IGUAL, vp.getProdProduto().getProdProdutoNcm());
                        FiltroNumero fn = new FiltroNumero("ibptTabela", ECompara.IGUAL, vp.getProdProduto().getProdProdutoTipo().equals("09") ? 1 : 0);
                        GrupoFiltro gf = new GrupoFiltro(EJuncao.E, new IFiltro[]{ft, fn});
                        Ibpt ibpt = service.selecionar(new Ibpt(), gf);

                        if (ibpt != null) {
                            char ori = vp.getProdProduto().getProdProdutoOrigem();
                            double taxa = (ori == '0' || ori == '3' || ori == '4' || ori == '5') ? ibpt.getIbptAliqNac() : ibpt.getIbptAliqImp();
                            double rateado = vp.getEcfVendaProdutoBruto() * porcent;
                            impostos += (vp.getEcfVendaProdutoBruto() + rateado) * vp.getEcfVendaProdutoQuantidade() * taxa / 100;
                        }
                    }
                }
                double porcentagem = impostos / (bruto + acres_desc) * 100;
                sb.append("Val Aprox Trib R$ ");
                sb.append(Util.formataNumero(impostos, 1, 2, false).replace(",", ".")).append(" [");
                sb.append(Util.formataNumero(porcentagem, 1, 2, false).replace(",", ".")).append("%] Fonte: IBPT").append(ECF.SL);
            }

            String[] resp = ECF.enviar(EComandoECF.ECF_SubtotalizaCupom, AD, sb.toString());
            if (ECF.ERRO.equals(resp[0])) {
                log.error("Erro ao fechar a venda. -> " + resp[1]);
                throw new OpenPdvException(resp[1]);
            }
            // soma os pagamento que possuem o mesmo codigo
            SortedMap<String, Double> pags = new TreeMap<>();
            for (EcfPagamento pag : pagamentos) {
                if (pags.containsKey(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo())) {
                    double valor = pag.getEcfPagamentoValor() + pags.get(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo());
                    pags.put(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo(), valor);
                } else {
                    pags.put(pag.getEcfPagamentoTipo().getEcfPagamentoTipoCodigo(), pag.getEcfPagamentoValor());
                }
            }
            // garante que o dinheiro é impressos primeiro
            String dinheiro = Util.getConfig().get("ecf.dinheiro");
            if (pags.containsKey(dinheiro)) {
                String valor = Util.formataNumero(pags.remove(dinheiro), 1, 2, false).replace(",", ".");
                ECF.enviar(EComandoECF.ECF_EfetuaPagamento, dinheiro, valor);
            }
            // garante que a troca é impressos em segundo se houver dinheiro
            String troca = Util.getConfig().get("ecf.troca");
            if (pags.containsKey(troca)) {
                String valor = Util.formataNumero(pags.remove(troca), 1, 2, false).replace(",", ".");
                ECF.enviar(EComandoECF.ECF_EfetuaPagamento, troca, valor);
            }
            // imprime os demais
            for (Entry<String, Double> pag : pags.entrySet()) {
                String valor = Util.formataNumero(pag.getValue(), 1, 2, false).replace(",", ".");
                ECF.enviar(EComandoECF.ECF_EfetuaPagamento, pag.getKey(), valor);
            }
            // fecha a venda
            resp = ECF.enviar(EComandoECF.ECF_FechaCupom);
            if (ECF.ERRO.equals(resp[0])) {
                log.error("Erro ao fechar a venda. -> " + resp[1]);
                throw new OpenPdvException(resp[1]);
            } else {
                // atualiza o gt
                try {
                    resp = ECF.enviar(EComandoECF.ECF_GrandeTotal);
                    if (ECF.OK.equals(resp[0])) {
                        PAF.AUXILIAR.setProperty("ecf.gt", resp[1]);
                        PAF.criptografar();
                    } else {
                        throw new Exception(resp[1]);
                    }
                } catch (Exception ex) {
                    log.error("Erro ao atualizar o GT. -> ", ex);
                    throw new OpenPdvException("Erro ao atualizar o GT.");
                }
            }
        } catch (Exception ex) {
            TEF.bloquear(false);
            int escolha = JOptionPane.showOptionDialog(null, "Impressora não responde, tentar novamente?", "TEF",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, new String[]{"SIM", "NÃO"}, JOptionPane.YES_OPTION);
            TEF.bloquear(true);
            if (escolha == JOptionPane.YES_OPTION) {
                fecharVendaECF();
            } else {
                throw new OpenPdvException(ex);
            }
        }
    }

    /**
     * Metodo para fechar uma venda no BD.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaBanco() throws OpenPdvException {
        // fecha a venda
        List<Sql> sqls = new ArrayList<>();
        FiltroNumero fn = new FiltroNumero("ecfVendaId", ECompara.IGUAL, venda.getId());
        ParametroNumero pn1 = new ParametroNumero("ecfVendaBruto", bruto);
        ParametroNumero pn2 = new ParametroNumero(acres_desc > 0 ? "ecfVendaAcrescimo" : "ecfVendaDesconto", Math.abs(acres_desc));
        ParametroNumero pn3 = new ParametroNumero("ecfVendaLiquido", bruto + acres_desc);
        ParametroBinario pb = new ParametroBinario("ecfVendaFechada", true);
        ParametroObjeto po1 = new ParametroObjeto("sisCliente", venda.getSisCliente());
        ParametroObjeto po2 = new ParametroObjeto("sisVendedor", venda.getSisVendedor());
        ParametroObjeto po3 = new ParametroObjeto("sisGerente", venda.getSisGerente());
        ParametroObjeto po4 = new ParametroObjeto("ecfTroca", venda.getEcfTroca());
        ParametroTexto pt = new ParametroTexto("ecfVendaObservacao", obs);
        GrupoParametro gp = new GrupoParametro(new IParametro[]{pn1, pn2, pn3, pb, po1, po2, po3, po4, pt});
        Sql sql = new Sql(new EcfVenda(), EComandoSQL.ATUALIZAR, fn, gp);
        sqls.add(sql);

        // atualiza a troca para ativo = true, se a venda estiver usando uma
        if (venda.getEcfTroca() != null) {
            FiltroNumero fn1 = new FiltroNumero("ecfTrocaId", ECompara.IGUAL, venda.getEcfTroca().getEcfTrocaId());
            ParametroBinario pb1 = new ParametroBinario("ecfTrocaAtivo", true);
            Sql sql1 = new Sql(venda.getEcfTroca(), EComandoSQL.ATUALIZAR, fn1, pb1);
            sqls.add(sql1);

            // atualiza o estoque
            for (EcfTrocaProduto tp : venda.getEcfTroca().getEcfTrocaProdutos()) {
                // fatorando a quantida no estoque
                double qtd = tp.getEcfTrocaProdutoQuantidade();
                if (tp.getProdEmbalagem().getProdEmbalagemId() != tp.getProdProduto().getProdEmbalagem().getProdEmbalagemId()) {
                    qtd *= tp.getProdEmbalagem().getProdEmbalagemUnidade();
                    qtd /= tp.getProdProduto().getProdEmbalagem().getProdEmbalagemUnidade();
                }

                // atualiza o estoque
                ParametroFormula pf = new ParametroFormula("prodProdutoEstoque", qtd);
                FiltroNumero fn2 = new FiltroNumero("prodProdutoId", ECompara.IGUAL, tp.getProdProduto().getId());
                Sql sql2 = new Sql(tp.getProdProduto(), EComandoSQL.ATUALIZAR, fn2, pf);
                sqls.add(sql2);
                // adiciona estoque da grade caso o produto tenha
                if (tp.getProdProduto().getProdGrades() != null) {
                    for (ProdGrade grade : tp.getProdProduto().getProdGrades()) {
                        if (grade.getProdGradeBarra().equals(tp.getEcfTrocaProdutoBarra())) {
                            ParametroFormula pf2 = new ParametroFormula("prodGradeEstoque", qtd);
                            FiltroNumero fn3 = new FiltroNumero("prodGradeId", ECompara.IGUAL, grade.getId());
                            Sql sql3 = new Sql(grade, EComandoSQL.ATUALIZAR, fn3, pf2);
                            sqls.add(sql3);
                            break;
                        }
                    }
                }
            }
        }

        // atualiza estoque e produtos
        double porcentagem = acres_desc / bruto;
        for (EcfVendaProduto vp : venda.getEcfVendaProdutos()) {
            if (porcentagem != 0) {
                double rateado = vp.getEcfVendaProdutoBruto() * porcentagem;
                FiltroNumero vp_fn = new FiltroNumero("ecfVendaProdutoId", ECompara.IGUAL, vp.getId());
                ParametroNumero vp_pn1 = new ParametroNumero(porcentagem > 0 ? "ecfVendaProdutoAcrescimo" : "ecfVendaProdutoDesconto", Math.abs(rateado));
                ParametroNumero vp_pn2 = new ParametroNumero("ecfVendaProdutoLiquido", vp.getEcfVendaProdutoBruto() + rateado);
                ParametroNumero vp_pn3 = new ParametroNumero("ecfVendaProdutoTotal", (vp.getEcfVendaProdutoBruto() + rateado) * vp.getEcfVendaProdutoQuantidade());
                GrupoParametro vp_gp = new GrupoParametro(new IParametro[]{vp_pn1, vp_pn2, vp_pn3});
                Sql sql1 = new Sql(new EcfVendaProduto(), EComandoSQL.ATUALIZAR, vp_fn, vp_gp);
                sqls.add(sql1);
            }

            if (!vp.getEcfVendaProdutoCancelado()) {
                // fatorando a quantida no estoque
                double qtd = vp.getEcfVendaProdutoQuantidade();
                if (vp.getProdEmbalagem().getProdEmbalagemId() != vp.getProdProduto().getProdEmbalagem().getProdEmbalagemId()) {
                    qtd *= vp.getProdEmbalagem().getProdEmbalagemUnidade();
                    qtd /= vp.getProdProduto().getProdEmbalagem().getProdEmbalagemUnidade();
                }
                // atualiza o estoque
                ParametroFormula pf = new ParametroFormula("prodProdutoEstoque", -1 * qtd);
                FiltroNumero fn1 = new FiltroNumero("prodProdutoId", ECompara.IGUAL, vp.getProdProduto().getId());
                Sql sql2 = new Sql(vp.getProdProduto(), EComandoSQL.ATUALIZAR, fn1, pf);
                sqls.add(sql2);
                // remove estoque da grade caso o produto tenha
                if (vp.getProdProduto().getProdGrades() != null) {
                    for (ProdGrade grade : vp.getProdProduto().getProdGrades()) {
                        if (grade.getProdGradeBarra().equals(vp.getEcfVendaProdutoBarra())) {
                            ParametroFormula pf2 = new ParametroFormula("prodGradeEstoque", -1 * qtd);
                            FiltroNumero fn2 = new FiltroNumero("prodGradeId", ECompara.IGUAL, grade.getId());
                            Sql sql3 = new Sql(grade, EComandoSQL.ATUALIZAR, fn2, pf2);
                            sqls.add(sql3);
                            break;
                        }
                    }
                }
            }
        }
        CoreService service = new CoreService();
        service.executar(sqls.toArray(new Sql[]{}));
    }

    /**
     * Metodo para fechar uma venda na Tela.
     *
     * @exception OpenPdvException dispara caso nao consiga executar.
     */
    public void fecharVendaTela() throws OpenPdvException {
        Caixa.getInstancia().getBobina().removeAllElements();
        Caixa.getInstancia().modoDisponivel();
    }
}
